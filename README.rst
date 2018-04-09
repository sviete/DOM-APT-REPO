dom-apt-repo
---------------

This code is used to publish cross-compiled packages created using the
[AIS-LINUX-PACKAGES](https://github.com/sviete/AIS-LINUX-PACKAGES).

Usage instructions
------------------

Clone this repo and run


    ./dom-apt-repo-stable <path/AIS-LINUX-PACKAGES/debs>

(the script should work on most Linux distributions).

All the .deb files in the directory from parameter will be published to a APT repository in the ./dists directory (which will be cleaned, so take caution).

Publishing the generated folder
-------------------------------

The published folder can be made available at a publicly accessible at
``$REPO_URL`` using the git push method.


Accessing the repository
------------------------

With the created ``<apt-repository-directory>`` available at
[DOM-APT-REPO](https://sviete.github.io/DOM-APT-REPO),

in the AIS Linux users can access this repo by creating a file:

::

    $PREFIX/etc/apt/sources.list.d

containing the single line:extras

::

    deb [trusted=yes] https://sviete.github.io/DOM-APT-REPO dom stable
